using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tandem.Server.Data.Migrations.Postgres
{
    /// <inheritdoc />
    public partial class AddInstallSystem : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "System",
                table: "InstallRecords",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "System",
                table: "InstallRecords");
        }
    }
}
